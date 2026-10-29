package dev.stint.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stint.shared.ClientController
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.FocusSession
import dev.stint.shared.Subject
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

private enum class RecordPeriod(val days: Int, val label: String) {
    DAY(1, "오늘"), WEEK(7, "7일"), MONTH(30, "30일")
}

@Composable
internal fun RecordsTab(
    controller: ClientController,
    state: ClientState,
    nowEpochMs: Long,
    selectedSubjectId: String?,
    onSelectSubject: (String) -> Unit,
    wide: Boolean,
    foldPosture: FoldPosture,
) {
    val snapshot = state.snapshot
    val subjects = snapshot?.subjects.orEmpty()
    var periodName by rememberSaveable { mutableStateOf(RecordPeriod.WEEK.name) }
    var allSubjects by rememberSaveable { mutableStateOf(true) }
    val period = RecordPeriod.entries.firstOrNull { it.name == periodName } ?: RecordPeriod.WEEK
    val today = localDate(nowEpochMs)
    val startDate = today.minus(period.days - 1, DateTimeUnit.DAY)
    val filtered = snapshot?.sessions.orEmpty()
        .filter { it.localDay() >= startDate && it.localDay() <= today }
        .filter { allSubjects || it.subjectId == selectedSubjectId }
        .sortedByDescending { it.startedAtEpochMs }
    val connected = state.connection == ConnectionStatus.CONNECTED && !state.working

    val overview: @Composable (Modifier, Boolean) -> Unit = { modifier, scrollable ->
        Column(
            modifier
                .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
                .padding(horizontal = if (wide) 34.dp else 20.dp, vertical = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    SectionEyebrow("기록")
                    Spacer(Modifier.height(6.dp))
                    Text("시간의 결", style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(Modifier.weight(1f))
                PeriodSwitch(period) { periodName = it.name }
            }
            Spacer(Modifier.height(22.dp))
            RecordSubjectFilter(
                subjects = subjects.filterNot { it.archived },
                selectedId = selectedSubjectId,
                all = allSubjects,
                onAll = { allSubjects = true },
                onSelect = { allSubjects = false; onSelectSubject(it) },
            )
            Spacer(Modifier.height(36.dp))
            Metrics(filtered)
            Spacer(Modifier.height(34.dp))
            ThinRule()
            Spacer(Modifier.height(28.dp))
            Text("최근 5주", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("색이 짙을수록 오래 머문 날이에요.", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            ActivityHeatmap(snapshot?.sessions.orEmpty().filter { allSubjects || it.subjectId == selectedSubjectId }, today)
            Spacer(Modifier.height(34.dp))
            Text("지난 7일", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(18.dp))
            WeekBars(snapshot?.sessions.orEmpty().filter { allSubjects || it.subjectId == selectedSubjectId }, today)
        }
    }

    val ledger: @Composable (Modifier, Boolean) -> Unit = { modifier, scrollable ->
        RecordLedger(
            subjects = subjects,
            sessions = filtered,
            nowEpochMs = nowEpochMs,
            period = period,
            modifier = modifier,
            scrollable = scrollable,
            canEdit = connected,
            onMemo = controller::saveMemo,
        )
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

        when {
            validTabletop -> Column(Modifier.fillMaxSize()) {
                overview(Modifier.fillMaxWidth().height(hingeTop.dp), true)
                Spacer(Modifier.height(foldPosture.hingeHeightDp.dp))
                ledger(Modifier.fillMaxWidth().weight(1f), true)
            }

            validBook -> Row(Modifier.fillMaxSize()) {
                overview(Modifier.width(hingeLeft.dp).fillMaxHeight(), true)
                Spacer(Modifier.width(foldPosture.hingeWidthDp.dp))
                ledger(Modifier.weight(1f).fillMaxHeight(), true)
            }

            wide -> Row(Modifier.fillMaxSize()) {
                overview(Modifier.weight(1.2f).fillMaxHeight(), true)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Rule.copy(alpha = 0.75f)))
                ledger(Modifier.weight(0.85f).fillMaxHeight(), true)
            }

            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                overview(Modifier.fillMaxWidth(), false)
                ThinRule(Modifier.padding(horizontal = 20.dp))
                ledger(Modifier.fillMaxWidth(), false)
            }
        }
    }
}

@Composable
private fun PeriodSwitch(period: RecordPeriod, onSelect: (RecordPeriod) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(PaperDeep).padding(3.dp)) {
        RecordPeriod.entries.forEach { value ->
            TextButton(
                onClick = { onSelect(value) },
                modifier = Modifier.height(48.dp),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    containerColor = if (value == period) PaperRaised else Color.Transparent,
                    contentColor = if (value == period) Ink else InkMuted,
                ),
                shape = RoundedCornerShape(50),
            ) { Text(value.label, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun RecordSubjectFilter(
    subjects: List<Subject>,
    selectedId: String?,
    all: Boolean,
    onAll: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip("전체", all, onAll)
        subjects.forEach { FilterChip(it.name, !all && it.id == selectedId) { onSelect(it.id) } }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Ink else PaperDeep)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, color = if (selected) PaperRaised else Ink, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Metrics(sessions: List<FocusSession>) {
    val total = sessions.sumOf { it.durationSeconds }
    val average = if (sessions.isEmpty()) 0 else total / sessions.size
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Metric("모인 시간", formatDuration(total), Modifier.weight(1.25f))
        Metric("집중", "${sessions.size}회", Modifier.weight(0.8f))
        Metric("평균", formatDuration(average), Modifier.weight(1f))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = InkMuted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActivityHeatmap(sessions: List<FocusSession>, today: LocalDate) {
    val days = (34 downTo 0).map { today.minus(it, DateTimeUnit.DAY) }
    val durations = days.map { date -> sessions.filter { it.localDay() == date }.sumOf { it.durationSeconds } }
    val max = durations.maxOrNull()?.coerceAtLeast(1) ?: 1
    val activeDays = durations.count { it > 0 }
    Canvas(
        Modifier.fillMaxWidth().height(112.dp).semantics {
            contentDescription = "최근 5주 중 ${activeDays}일에 집중 기록이 있어요."
        },
    ) {
        val columns = 5
        val rows = 7
        val gap = 6.dp.toPx()
        val cell = minOf((size.width - gap * (columns - 1)) / columns, (size.height - gap * (rows - 1)) / rows)
        val startX = size.width - (cell * columns + gap * (columns - 1))
        durations.forEachIndexed { index, duration ->
            val column = index / rows
            val row = index % rows
            val fraction = duration.toFloat() / max
            val color = when {
                duration == 0 -> Rule.copy(alpha = 0.35f)
                fraction < 0.34f -> ForestWash
                fraction < 0.68f -> Forest.copy(alpha = 0.58f)
                else -> Forest
            }
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(startX + column * (cell + gap), row * (cell + gap)),
                size = androidx.compose.ui.geometry.Size(cell, cell),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

@Composable
private fun WeekBars(sessions: List<FocusSession>, today: LocalDate) {
    val days = (6 downTo 0).map { today.minus(it, DateTimeUnit.DAY) }
    val durations = days.map { date -> sessions.filter { it.localDay() == date }.sumOf { it.durationSeconds } }
    val max = durations.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEachIndexed { index, date ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.46f)
                            .fillMaxHeight((durations[index].toFloat() / max).coerceAtLeast(if (durations[index] > 0) 0.06f else 0f))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (date == today) Clay else Forest.copy(alpha = 0.72f)),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(weekdayKorean(date), color = if (date == today) Ink else InkMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RecordLedger(
    subjects: List<Subject>,
    sessions: List<FocusSession>,
    nowEpochMs: Long,
    period: RecordPeriod,
    modifier: Modifier,
    scrollable: Boolean,
    canEdit: Boolean,
    onMemo: (String, String) -> Unit,
) {
    Column(
        modifier
            .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        SectionEyebrow(period.label)
        Spacer(Modifier.height(8.dp))
        Text("집중 기록", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(22.dp))
        ThinRule()
        if (sessions.isEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text("이 기간에는 기록이 없어요.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("기간이나 주제를 바꿔보세요.", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
        } else {
            sessions.forEach { session ->
                LedgerRow(
                    session = session,
                    subject = subjects.firstOrNull { it.id == session.subjectId },
                    nowEpochMs = nowEpochMs,
                    onMemo = onMemo,
                    canEdit = canEdit,
                )
                ThinRule()
            }
        }
    }
}
