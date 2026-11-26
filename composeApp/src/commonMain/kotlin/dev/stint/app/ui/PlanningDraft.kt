package dev.stint.app.ui

import dev.stint.shared.HourPlan
import dev.stint.shared.PlanCycle

internal data class HourPlanDraft(
    val hour: Int,
    val title: String,
    val cycle: PlanCycle,
    val studyMinutes: List<Int>,
) {
    fun validatedPlan(): HourPlan? {
        val normalizedTitle = title.trim()
        if (hour !in 0..23 || normalizedTitle.isEmpty() || normalizedTitle.length > 40) return null
        if (studyMinutes.size != cycle.repetitions) return null
        if (studyMinutes.any { it !in 1 until cycle.cycleMinutes }) return null
        return HourPlan(hour, normalizedTitle, cycle, studyMinutes)
    }
}

internal fun HourPlan?.toDraft(hour: Int, defaultCycle: PlanCycle): HourPlanDraft = HourPlanDraft(
    hour = hour,
    title = this?.title.orEmpty(),
    cycle = this?.cycle ?: defaultCycle,
    studyMinutes = this?.studyMinutes?.takeIf { values ->
        values.size == this.cycle.repetitions && values.all { it in 1 until this.cycle.cycleMinutes }
    } ?: (this?.cycle ?: defaultCycle).defaultStudyMinutes(),
)

internal fun HourPlanDraft.withCycle(next: PlanCycle): HourPlanDraft =
    copy(cycle = next, studyMinutes = next.defaultStudyMinutes())

internal fun HourPlanDraft.phaseMinutes(phaseIndex: Int): Int? {
    if (phaseIndex < 0) return null
    val cycleIndex = phaseIndex / 2
    val study = studyMinutes.getOrNull(cycleIndex) ?: return null
    return if (phaseIndex % 2 == 0) study else cycle.cycleMinutes - study
}

internal fun HourPlanDraft.withPhaseMinutes(phaseIndex: Int, minutes: Int): HourPlanDraft? {
    if (phaseIndex < 0) return null
    val cycleIndex = phaseIndex / 2
    val currentStudy = studyMinutes.getOrNull(cycleIndex) ?: return null
    if (minutes !in 1 until cycle.cycleMinutes) return null
    val nextStudy = if (phaseIndex % 2 == 0) minutes else cycle.cycleMinutes - minutes
    if (nextStudy == currentStudy) return this
    return copy(studyMinutes = studyMinutes.toMutableList().also { it[cycleIndex] = nextStudy })
}

internal fun planningClock(minutes: Int): String {
    val safe = minutes.coerceIn(0, 24 * 60)
    return "${(safe / 60).toString().padStart(2, '0')}:${(safe % 60).toString().padStart(2, '0')}"
}

internal fun planningRange(fromMinutes: Int, toMinutes: Int): String =
    "${planningClock(fromMinutes)}–${planningClock(toMinutes)}"

internal data class PlanPhase(
    val index: Int,
    val cycleIndex: Int,
    val study: Boolean,
    val fromMinute: Int,
    val toMinute: Int,
) {
    val minutes: Int get() = toMinute - fromMinute
}

internal fun HourPlanDraft.phases(): List<PlanPhase> {
    val result = mutableListOf<PlanPhase>()
    studyMinutes.forEachIndexed { cycleIndex, study ->
        val cycleStart = cycleIndex * cycle.cycleMinutes
        result += PlanPhase(cycleIndex * 2, cycleIndex, true, cycleStart, cycleStart + study)
        result += PlanPhase(cycleIndex * 2 + 1, cycleIndex, false, cycleStart + study, cycleStart + cycle.cycleMinutes)
    }
    return result
}
