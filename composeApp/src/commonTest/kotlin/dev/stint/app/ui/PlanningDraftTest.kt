package dev.stint.app.ui

import dev.stint.shared.HourPlan
import dev.stint.shared.PlanCycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanningDraftTest {
    @Test
    fun `short and long defaults fill exactly one hour`() {
        for (cycle in PlanCycle.entries) {
            val draft = null.toDraft(hour = 14, defaultCycle = cycle)
            assertEquals(60, draft.phases().sumOf { it.minutes })
            assertEquals(cycle.repetitions * 2, draft.phases().size)
        }
    }

    @Test
    fun `changing one phase preserves its paired cycle duration`() {
        val initial = null.toDraft(8, PlanCycle.FIFTEEN_FIVE)
        val changedStudy = initial.withPhaseMinutes(0, 13)!!
        assertEquals(listOf(13, 7), changedStudy.phases().take(2).map { it.minutes })
        val changedRest = changedStudy.withPhaseMinutes(1, 5)!!
        assertEquals(listOf(15, 5), changedRest.phases().take(2).map { it.minutes })
        assertEquals(60, changedRest.phases().sumOf { it.minutes })
    }

    @Test
    fun `invalid duration and blank title cannot be saved`() {
        val draft = null.toDraft(3, PlanCycle.TWENTY_FIVE_FIVE)
        assertNull(draft.phaseMinutes(-1))
        assertNull(draft.withPhaseMinutes(-1, 5))
        assertNull(draft.withPhaseMinutes(0, 0))
        assertNull(draft.withPhaseMinutes(0, 30))
        assertNull(draft.validatedPlan())
        assertEquals("제목", draft.copy(title = " 제목 ").validatedPlan()?.title)
    }

    @Test
    fun `stored custom cycle durations are restored`() {
        val plan = HourPlan(23, "마무리", PlanCycle.TWENTY_FIVE_FIVE, listOf(23, 27))
        val draft = plan.toDraft(23, PlanCycle.FIFTEEN_FIVE)
        assertEquals(listOf(23, 7, 27, 3), draft.phases().map { it.minutes })
        assertEquals("23:55", planningClock(23 * 60 + 55))
        assertEquals("23:55–24:00", planningRange(23 * 60 + 55, 24 * 60))
    }
}
