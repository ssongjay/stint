package dev.stint.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stint.shared.Subject

private val WorkbenchColors = listOf(
    "466B57", "B9684C", "B18A3E", "566A7E", "755F78", "8B5B48", "49736F", "8A6552",
)

@Composable
internal fun SubjectEditor(
    original: Subject?,
    nowEpochMs: Long,
    onSave: (Subject) -> Unit,
    onClose: () -> Unit,
) {
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var color by rememberSaveable(original?.id) { mutableStateOf(original?.colorHex ?: WorkbenchColors.first()) }
    var focusMinutes by rememberSaveable(original?.id) { mutableStateOf(((original?.focusSeconds ?: 1500) / 60).toString()) }
    var shortBreakMinutes by rememberSaveable(original?.id) { mutableStateOf(((original?.shortBreakSeconds ?: 300) / 60).toString()) }
    var longBreakMinutes by rememberSaveable(original?.id) { mutableStateOf(((original?.longBreakSeconds ?: 900) / 60).toString()) }
    var longBreakEvery by rememberSaveable(original?.id) { mutableStateOf((original?.longBreakEvery ?: 4).toString()) }
    var error by rememberSaveable(original?.id) { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
            SectionEyebrow(if (original == null) "새 주제" else "주제 설정")
            Spacer(Modifier.height(6.dp))
            Text(if (original == null) "무엇에 머물까요" else original.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            Text("이름과 흐름은 언제든 다시 바꿀 수 있어요. 지난 기록은 그대로 남아요.", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 80) { name = it; error = null } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("주제 이름") },
                placeholder = { Text("예: 알고리즘 공부") },
                singleLine = true,
            )
            Spacer(Modifier.height(22.dp))
            Text("색", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WorkbenchColors.forEach { value ->
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(parseSubjectColor(value))
                            .then(if (color == value) Modifier.border(3.dp, PaperRaised, CircleShape).border(1.dp, Ink, CircleShape) else Modifier)
                            .selectable(selected = color == value, role = Role.RadioButton) { color = value },
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
            ThinRule()
            Spacer(Modifier.height(24.dp))
            Text("집중 흐름", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MinuteField("집중", focusMinutes, { focusMinutes = numeric(it); error = null }, Modifier.weight(1f))
                MinuteField("짧은 휴식", shortBreakMinutes, { shortBreakMinutes = numeric(it); error = null }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MinuteField("긴 휴식", longBreakMinutes, { longBreakMinutes = numeric(it); error = null }, Modifier.weight(1f))
                MinuteField("긴 휴식 간격", longBreakEvery, { longBreakEvery = numeric(it); error = null }, Modifier.weight(1f), suffix = "회")
            }
            if (error != null) {
                Spacer(Modifier.height(14.dp))
                Text(error.orEmpty(), color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val focus = focusMinutes.toIntOrNull()
                        val short = shortBreakMinutes.toIntOrNull()
                        val long = longBreakMinutes.toIntOrNull()
                        val every = longBreakEvery.toIntOrNull()
                        error = when {
                            name.isBlank() -> "주제 이름을 입력해 주세요."
                            focus == null || focus !in 1..240 -> "집중 시간은 1분부터 240분까지 입력할 수 있어요."
                            short == null || short !in 1..60 -> "짧은 휴식은 1분부터 60분까지 입력할 수 있어요."
                            long == null || long !in 1..120 -> "긴 휴식은 1분부터 120분까지 입력할 수 있어요."
                            every == null || every !in 2..12 -> "긴 휴식 간격은 2회부터 12회까지 입력할 수 있어요."
                            else -> null
                        }
                        if (error == null) {
                            onSave(
                                Subject(
                                    id = original?.id ?: "subject-${nowEpochMs.toString(36)}",
                                    name = name.trim(),
                                    colorHex = color,
                                    focusSeconds = focus!! * 60,
                                    shortBreakSeconds = short!! * 60,
                                    longBreakSeconds = long!! * 60,
                                    longBreakEvery = every!!,
                                    archived = original?.archived ?: false,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("저장") }
                OutlinedButton(onClick = onClose, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(8.dp)) { Text("취소") }
                Spacer(Modifier.weight(1f))
                if (original != null) {
                    TextButton(onClick = { onSave(original.copy(archived = true)) }, modifier = Modifier.height(52.dp)) { Text("주제 보관", color = ErrorRed) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("보관한 주제의 기록은 통계와 회고에 계속 남아요.", color = InkMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MinuteField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier,
    suffix: String = "분",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun numeric(value: String): String = value.filter(Char::isDigit).take(3)
