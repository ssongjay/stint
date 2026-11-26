package dev.stint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import dev.stint.shared.ClientController
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.DayPlan
import dev.stint.shared.HourPlan
import dev.stint.shared.PlanCycle
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

private val MorningPlan = Color(0xFFECE3CE)
private val AfternoonPlan = Color(0xFFD6DED2)
private val PlanGold = Color(0xFFB08B46)

private enum class PlanPeriod { AM, PM }

private data class PendingPlanSave(val hour: Int, val expectedRevision: Long, val plan: HourPlan?)

@Composable
internal fun PlanTab(
    controller: ClientController,
    state: ClientState,
    nowEpochMs: Long,
    foldPosture: FoldPosture,
) {
    val today = localDate(nowEpochMs)
    var selectedDate by remember { mutableStateOf(today) }
    var period by remember { mutableStateOf(PlanPeriod.AM) }
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf<HourPlanDraft?>(null) }
    var expectedPlanRevision by remember { mutableLongStateOf(0L) }
    var selectedPhase by remember { mutableIntStateOf(-1) }
    var phaseMinutesText by remember { mutableStateOf("") }
    var defaultPanel by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<PendingPlanSave?>(null) }
    var planConflict by remember { mutableStateOf(false) }

    val planning = state.planning
    val day = planning.day?.takeIf { it.date == selectedDate.toString() }
    val connected = state.connection == ConnectionStatus.CONNECTED && !planning.saving

    LaunchedEffect(selectedDate) {
        selectedHour = null
        selectedPhase = -1
        draft = null
        pendingSave = null
        planConflict = false
        controller.selectPlanDate(selectedDate.toString())
    }
    DisposableEffect(controller) {
        onDispose { controller.selectPlanDate(null) }
    }
    LaunchedEffect(planning.saving, day?.revision, planning.error, pendingSave) {
        val pending = pendingSave ?: return@LaunchedEffect
        if (!planning.saving && day != null && day.revision > pending.expectedRevision) {
            val saved = day.hours.firstOrNull { it.hour == pending.hour }
            if (saved == pending.plan) {
                expectedPlanRevision = day.revision
                pendingSave = null
                planConflict = false
                if (saved == null) {
                    selectedHour = null
                    draft = null
                    selectedPhase = -1
                    highlighted = null
                } else {
                    draft = saved.toDraft(pending.hour, day.defaultCycle)
                }
            } else {
                pendingSave = null
                planConflict = true
            }
        }
    }
    LaunchedEffect(day?.revision, expectedPlanRevision, selectedHour) {
        if (selectedHour != null && draft != null && day != null && day.revision != expectedPlanRevision && pendingSave == null) {
            planConflict = true
        }
    }

    fun chooseDate(next: LocalDate) {
        selectedDate = next
        defaultPanel = false
        planConflict = false
    }
    fun openHour(hour: Int) {
        val currentDay = day ?: return
        selectedHour = hour
        draft = currentDay.hours.firstOrNull { it.hour == hour }.toDraft(hour, currentDay.defaultCycle)
        expectedPlanRevision = currentDay.revision
        selectedPhase = -1
        highlighted = null
        defaultPanel = false
    }
    fun closeHour() {
        selectedHour = null
        draft = null
        selectedPhase = -1
        highlighted = null
        pendingSave = null
        planConflict = false
    }
    fun saveHour(plan: HourPlan?) {
        val hour = selectedHour ?: return
        val expected = expectedPlanRevision
        pendingSave = PendingPlanSave(hour, expected, plan)
        controller.saveHourPlan(selectedDate.toString(), hour, plan, expected)
    }

    val clockDraft = draft?.let { current ->
        val minutes = phaseMinutesText.toIntOrNull()
        if (selectedPhase >= 0 && minutes != null) current.withPhaseMinutes(selectedPhase, minutes) ?: current else current
    }

    val clock: @Composable (Modifier) -> Unit = { modifier ->
        PlanningClock(
            modifier = modifier,
            period = period,
            day = day,
            draft = clockDraft,
            selectedHour = selectedHour,
            selectedPhase = selectedPhase,
            highlighted = highlighted,
            onHighlight = { highlighted = it },
            onSelect = { index ->
                if (selectedHour == null) openHour((if (period == PlanPeriod.AM) 0 else 12) + index)
                else {
                    selectedPhase = index
                    phaseMinutesText = draft?.phaseMinutes(index)?.toString().orEmpty()
                }
            },
            onPeriod = {
                if (selectedHour == null) {
                    period = it
                    highlighted = null
                }
            },
            onBack = ::closeHour,
        )
    }
    val details: @Composable (Modifier, Boolean) -> Unit = { modifier, scrollable ->
        val base = modifier.let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
        val editingDraft = draft
        Column(base.padding(horizontal = 22.dp, vertical = 22.dp)) {
            if (editingDraft == null && selectedPhase < 0) {
                PlanDateHeader(
                    selectedDate = selectedDate,
                    today = today,
                    onDate = ::chooseDate,
                    onDefaults = { defaultPanel = true; closeHour() },
                )
                Spacer(Modifier.height(16.dp))
            }
            when {
                editingDraft != null && selectedPhase >= 0 -> PhaseEditor(
                    draft = clockDraft ?: editingDraft,
                    phaseIndex = selectedPhase,
                    phaseMinutesText = phaseMinutesText,
                    enabled = connected,
                    onPhaseText = { phaseMinutesText = it.filter(Char::isDigit).take(2) },
                    onApply = {
                        val minutes = phaseMinutesText.toIntOrNull() ?: return@PhaseEditor
                        draft = editingDraft.withPhaseMinutes(selectedPhase, minutes) ?: return@PhaseEditor
                        selectedPhase = -1
                    },
                    onClose = { selectedPhase = -1 },
                )
                defaultPanel -> PlanDefaultPanel(
                    day = day,
                    enabled = connected,
                    saving = planning.saving,
                    onCycle = { cycle -> day?.let { controller.savePlanDefault(cycle, it.revision) } },
                    onClose = { defaultPanel = false },
                )
                editingDraft != null && selectedHour != null -> HourPlanEditor(
                    draft = editingDraft,
                    enabled = connected,
                    saving = planning.saving,
                    isExisting = day?.hours?.any { it.hour == selectedHour } == true,
                    conflict = planConflict,
                    onDraft = { draft = it },
                    onCycle = { draft = editingDraft.withCycle(it); selectedPhase = -1 },
                    onPhase = { phase ->
                        selectedPhase = phase
                        phaseMinutesText = editingDraft.phaseMinutes(phase)?.toString().orEmpty()
                    },
                    onSave = { draft?.validatedPlan()?.let(::saveHour) },
                    onDelete = { saveHour(null) },
                    onReload = {
                        val hour = selectedHour ?: return@HourPlanEditor
                        val latest = day ?: return@HourPlanEditor
                        draft = latest.hours.firstOrNull { it.hour == hour }.toDraft(hour, latest.defaultCycle)
                        expectedPlanRevision = latest.revision
                        selectedPhase = -1
                        pendingSave = null
                        planConflict = false
                    },
                    onClose = ::closeHour,
                )
                else -> DayPlanOverview(
                    day = day,
                    loading = planning.loading,
                    enabled = connected,
                    period = period,
                    onHour = ::openHour,
                    onReload = { controller.selectPlanDate(selectedDate.toString()) },
                )
            }
            planning.error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.connection == ConnectionStatus.OFFLINE) {
                Spacer(Modifier.height(12.dp))
                Text("연결이 복구되면 계획을 수정할 수 있어요.", color = InkMuted)
            }
        }
    }

    val density = LocalDensity.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("plan-tab").onGloballyPositioned { bounds = it.boundsInWindow() }) {
        val originX = with(density) { bounds.left.toDp().value }
        val originY = with(density) { bounds.top.toDp().value }
        val fold = resolveFoldContentGeometry(foldPosture, originX, originY, maxWidth.value, maxHeight.value)
        when {
            fold.validTabletop -> Column(Modifier.fillMaxSize()) {
                clock(Modifier.fillMaxWidth().height(fold.hingeTopDp.dp))
                Spacer(Modifier.height(foldPosture.hingeHeightDp.dp))
                details(Modifier.fillMaxWidth().weight(1f), true)
            }
            fold.validBook -> Row(Modifier.fillMaxSize()) {
                clock(Modifier.width(fold.hingeLeftDp.dp).fillMaxHeight())
                Spacer(Modifier.width(foldPosture.hingeWidthDp.dp))
                details(Modifier.weight(1f).fillMaxHeight(), true)
            }
            maxWidth >= 840.dp -> Row(Modifier.fillMaxSize()) {
                clock(Modifier.weight(1.05f).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(Rule.copy(alpha = .75f)))
                details(Modifier.weight(.95f).fillMaxHeight(), true)
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                clock(Modifier.fillMaxWidth().height(370.dp))
                ThinRule(Modifier.padding(horizontal = 20.dp))
                details(Modifier.fillMaxWidth(), false)
            }
        }
    }
}

@Composable
private fun PlanningClock(
    modifier: Modifier,
    period: PlanPeriod,
    day: DayPlan?,
    draft: HourPlanDraft?,
    selectedHour: Int?,
    selectedPhase: Int,
    highlighted: Int?,
    onHighlight: (Int?) -> Unit,
    onSelect: (Int) -> Unit,
    onPeriod: (PlanPeriod) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (selectedHour == null) {
            PeriodSwitch(period, onPeriod)
        } else {
            TextButton(onClick = onBack) { Text("‹ ${if (selectedHour < 12) "오전" else "오후"} 시간표") }
        }
        Spacer(Modifier.height(4.dp))
        val segments = if (draft == null) {
            val first = if (period == PlanPeriod.AM) 0 else 12
            List(12) { index ->
                val plan = day?.hours?.firstOrNull { it.hour == first + index }
                PlanningDialSegment(
                    from = (index - .5f) / 12f,
                    to = (index + .5f) / 12f,
                    color = when {
                        plan != null && index % 3 == 0 -> Clay
                        plan != null && index % 2 == 0 -> Forest
                        plan != null -> PlanGold
                        period == PlanPeriod.AM -> MorningPlan
                        else -> AfternoonPlan
                    },
                )
            }
        } else {
            draft.phases().map { phase ->
                PlanningDialSegment(
                    from = phase.fromMinute / 60f,
                    to = phase.toMinute / 60f,
                    color = if (phase.study) Forest else PlanGold,
                )
            }
        }
        val active = highlighted ?: selectedPhase.takeIf { it >= 0 }
        val periodStart = if (period == PlanPeriod.AM) 0 else 12
        val hour = selectedHour ?: periodStart + (highlighted ?: 0)
        val phase = draft?.phases()?.getOrNull(active ?: -1)
        val centerCaption = when {
            draft == null -> if (period == PlanPeriod.AM) "오전" else "오후"
            phase != null -> "${phase.cycleIndex + 1}회차 · ${if (phase.study) "공부" else "휴식"}"
            else -> ""
        }
        val selectedHourValue = selectedHour ?: hour
        val centerTime = when {
            draft == null && highlighted == null -> planningRange(periodStart * 60, (periodStart + 12) * 60)
            draft == null -> planningRange(hour * 60, (hour + 1) * 60)
            phase != null -> planningRange(selectedHourValue * 60 + phase.fromMinute, selectedHourValue * 60 + phase.toMinute)
            else -> planningRange(selectedHourValue * 60, (selectedHourValue + 1) * 60)
        }
        val centerTitle = when {
            draft == null && highlighted == null -> "12개 시간 조각"
            draft == null -> day?.hours?.firstOrNull { it.hour == hour }?.title ?: "빈 시간"
            phase != null -> "${phase.minutes}분"
            else -> draft.title.ifBlank { "빈 시간" }
        }
        PlanningDial(
            segments = segments,
            isMinuteDial = draft != null,
            startHour = if (period == PlanPeriod.AM) 0 else 12,
            centerCaption = centerCaption,
            centerTime = centerTime,
            centerTitle = centerTitle,
            highlightedIndex = highlighted,
            selectedIndex = if (draft == null) selectedHour?.rem(12) else selectedPhase.takeIf { it >= 0 },
            onHighlight = onHighlight,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun PeriodSwitch(selected: PlanPeriod, onSelect: (PlanPeriod) -> Unit) {
    Row(Modifier.selectableGroup().clip(RoundedCornerShape(9.dp)).background(PaperDeep).padding(3.dp)) {
        PlanPeriod.entries.forEach { period ->
            val chosen = period == selected
            Row(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (chosen) PaperRaised else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(period) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen, onClick = null)
                Text(if (period == PlanPeriod.AM) "오전" else "오후")
            }
        }
    }
}

@Composable
private fun PlanDateHeader(selectedDate: LocalDate, today: LocalDate, onDate: (LocalDate) -> Unit, onDefaults: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 380.dp
        val dateNavigation: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onDate(selectedDate.minus(1, DateTimeUnit.DAY)) },
                    modifier = Modifier.semantics { contentDescription = "이전 날짜" },
                ) { Text("‹") }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${selectedDate.month.ordinal + 1}월 ${selectedDate.day}일", style = MaterialTheme.typography.titleLarge)
                    Text("${weekdayKorean(selectedDate)}요일", color = InkMuted, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = { onDate(selectedDate.plus(1, DateTimeUnit.DAY)) },
                    modifier = Modifier.semantics { contentDescription = "다음 날짜" },
                ) { Text("›") }
            }
        }
        val actions: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedDate != today) TextButton(onClick = { onDate(today) }) { Text("오늘") }
                OutlinedButton(onClick = onDefaults, modifier = Modifier.testTag("plan-default-cycle")) { Text("기본 사이클") }
            }
        }
        if (compact) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { dateNavigation() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                dateNavigation()
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
    }
}

@Composable
private fun DayPlanOverview(
    day: DayPlan?, loading: Boolean, enabled: Boolean, period: PlanPeriod,
    onHour: (Int) -> Unit, onReload: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (period == PlanPeriod.AM) "오전 일정" else "오후 일정", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        if (loading) Text("불러오는 중", color = InkMuted, style = MaterialTheme.typography.labelMedium)
    }
    Spacer(Modifier.height(16.dp))
    if (day == null) {
        Text("계획을 불러오면 시간을 선택할 수 있어요.", color = InkMuted)
        if (!loading) TextButton(onClick = onReload) { Text("다시 시도") }
        return
    }
    val first = if (period == PlanPeriod.AM) 0 else 12
    for (row in 0 until 3) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (column in 0 until 4) {
                val hour = first + row * 4 + column
                val plan = day.hours.firstOrNull { it.hour == hour }
                OutlinedButton(
                    onClick = { onHour(hour) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics {
                        contentDescription = "${planningRange(hour * 60, (hour + 1) * 60)} ${plan?.title ?: "빈 시간"}"
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(hour.toString().padStart(2, '0'), fontWeight = FontWeight.SemiBold)
                        Text(plan?.title ?: "빈 시간", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (plan == null) InkMuted else Ink, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun PlanDefaultPanel(day: DayPlan?, enabled: Boolean, saving: Boolean, onCycle: (PlanCycle) -> Unit, onClose: () -> Unit) {
    TextButton(onClick = onClose) { Text("‹ 일정 목록") }
    Text("기본 사이클", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    CycleChoices(day?.defaultCycle ?: PlanCycle.FIFTEEN_FIVE, enabled && !saving, onCycle)
    Spacer(Modifier.height(10.dp))
    Text("새로 만드는 빈 시간에 적용돼요.", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun HourPlanEditor(
    draft: HourPlanDraft,
    enabled: Boolean,
    saving: Boolean,
    isExisting: Boolean,
    conflict: Boolean,
    onDraft: (HourPlanDraft) -> Unit,
    onCycle: (PlanCycle) -> Unit,
    onPhase: (Int) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    TextButton(onClick = onClose) { Text("‹ 일정 목록") }
    Text(planningRange(draft.hour * 60, (draft.hour + 1) * 60), color = Forest, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    if (conflict) {
        Text("다른 기기에서 이 날짜의 계획이 바뀌었어요.", color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onReload) { Text("최신 계획 불러오기") }
        Spacer(Modifier.height(6.dp))
    }
    Text("사이클 유형", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    CycleChoices(draft.cycle, enabled) { onCycle(it) }
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = draft.title,
        onValueChange = { if (it.length <= 40) onDraft(draft.copy(title = it)) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("plan-hour-title"),
        label = { Text("시간 제목") },
        placeholder = { Text("전공 공부") },
        singleLine = true,
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = enabled && draft.validatedPlan() != null) { Text(if (saving) "저장 중" else "저장") }
        if (isExisting) TextButton(onClick = onDelete, enabled = enabled) { Text("계획 지우기", color = ErrorRed) }
    }
    Spacer(Modifier.height(18.dp))
    draft.phases().chunked(2).forEachIndexed { cycleIndex, phases ->
        Text("${cycleIndex + 1}회차", color = InkMuted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            phases.forEach { phase ->
                OutlinedButton(
                    onClick = { onPhase(phase.index) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${if (phase.study) "공부" else "휴식"} ${phase.minutes}분")
                        Text(planningRange(draft.hour * 60 + phase.fromMinute, draft.hour * 60 + phase.toMinute),
                            color = InkMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun PhaseEditor(
    draft: HourPlanDraft,
    phaseIndex: Int,
    phaseMinutesText: String,
    enabled: Boolean,
    onPhaseText: (String) -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
) {
    val phase = draft.phases().getOrNull(phaseIndex) ?: return
    TextButton(onClick = onClose) { Text("‹ 한 시간 설정") }
    Text(
        planningRange(draft.hour * 60 + phase.fromMinute, draft.hour * 60 + phase.toMinute),
        color = Forest,
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(4.dp))
    Text("${phase.cycleIndex + 1}회차 ${if (phase.study) "공부" else "휴식"}", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = phaseMinutesText,
        onValueChange = onPhaseText,
        enabled = enabled,
        modifier = Modifier.width(132.dp),
        label = { Text("시간") },
        suffix = { Text("분") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    val typed = phaseMinutesText.toIntOrNull()
    val pairedMinutes = typed?.takeIf { it > 0 && it < draft.cycle.cycleMinutes }
        ?.let { draft.cycle.cycleMinutes - it }
    Spacer(Modifier.height(8.dp))
    Text(
        if (pairedMinutes != null) "같은 회차의 ${if (phase.study) "휴식" else "공부"}이 ${pairedMinutes}분으로 맞춰져요."
        else "1분부터 ${draft.cycle.cycleMinutes - 1}분까지 설정할 수 있어요.",
        color = InkMuted,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(14.dp))
    Button(onClick = onApply, enabled = enabled && pairedMinutes != null) { Text("적용") }
}

@Composable
private fun CycleChoices(selected: PlanCycle, enabled: Boolean, onSelect: (PlanCycle) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        PlanCycle.entries.forEach { cycle ->
            val checked = selected == cycle
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (checked) ForestWash else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(cycle) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = checked, onClick = null, enabled = enabled)
                Text(if (cycle == PlanCycle.FIFTEEN_FIVE) "공부 15분 · 휴식 5분" else "공부 25분 · 휴식 5분")
                Spacer(Modifier.weight(1f))
                Text("${cycle.repetitions}회", color = InkMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
