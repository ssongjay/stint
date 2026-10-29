package dev.stint.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeSettingsPanelTest {
    @Test
    fun currentTargetMustBeLongerThanExactElapsedTime() {
        assertEquals("이미 지난 시간보다 길게 설정해 주세요.", currentDurationError(25, 240, 1_500_000))
        assertEquals("이미 지난 시간보다 길게 설정해 주세요.", currentDurationError(25, 240, 1_500_001))
        assertNull(currentDurationError(25, 240, 1_499_999))
        assertNull(currentDurationError(26, 240, 1_500_000))
    }

    @Test
    fun currentTargetUsesThePhaseLimit() {
        assertNull(currentDurationError(60, 60, 0))
        assertEquals(
            "전체 목표는 1분부터 60분까지 입력할 수 있어요.",
            currentDurationError(61, 60, 0),
        )
    }

    @Test
    fun defaultsValidateEveryIndependentDuration() {
        assertNull(defaultDurationError(25, 5, 15, 4))
        assertEquals("짧은 휴식은 1분부터 60분까지 입력할 수 있어요.", defaultDurationError(25, 0, 15, 4))
        assertEquals("긴 휴식 간격은 2회부터 12회까지 입력할 수 있어요.", defaultDurationError(25, 5, 15, 13))
    }
}
