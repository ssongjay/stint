package dev.stint.shared

import kotlinx.serialization.Serializable

@Serializable
enum class PlanCycle(val studyMinutes: Int, val restMinutes: Int, val repetitions: Int) {
    FIFTEEN_FIVE(studyMinutes = 15, restMinutes = 5, repetitions = 3),
    TWENTY_FIVE_FIVE(studyMinutes = 25, restMinutes = 5, repetitions = 2);

    val cycleMinutes: Int get() = studyMinutes + restMinutes
    fun defaultStudyMinutes(): List<Int> = List(repetitions) { studyMinutes }
}

/** Only the study durations are stored. Each paired rest fills the remainder of its cycle. */
@Serializable
data class HourPlan(
    val hour: Int,
    val title: String,
    val cycle: PlanCycle,
    val studyMinutes: List<Int> = cycle.defaultStudyMinutes(),
)

@Serializable
data class DayPlan(
    val date: String,
    val revision: Long,
    val defaultCycle: PlanCycle,
    val hours: List<HourPlan>,
)

@Serializable
data class SaveHourPlanRequest(
    val context: MutationContext,
    val expectedPlanRevision: Long,
    val plan: HourPlan?,
)

@Serializable
data class SavePlanDefaultRequest(
    val context: MutationContext,
    val expectedPlanRevision: Long,
    val cycle: PlanCycle,
)
