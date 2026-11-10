package dev.stint.app.ui

import dev.stint.shared.Subject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs

class TimeSettingsPanelTest {
    @Test
    fun currentTargetMustBeLongerThanExactElapsedTime() {
        assertEquals("이미 지난 시간보다 길게 설정해 주세요.", currentError(1500, 14_400, 1_500_000))
        assertEquals("이미 지난 시간보다 길게 설정해 주세요.", currentError(1500, 14_400, 1_500_001))
        assertNull(currentError(1500, 14_400, 1_499_999))
        assertNull(currentError(1501, 14_400, 1_500_000))
    }

    @Test
    fun currentTargetUsesThePhaseLimit() {
        assertNull(currentError(3600, 3600, 0))
        assertEquals(
            "전체 목표는 1초부터 60분까지 입력할 수 있어요.",
            currentError(3601, 3600, 0),
        )
    }

    @Test
    fun defaultsValidateEveryIndependentDuration() {
        assertNull(defaultsError(1500, 300, 900, 4))
        assertEquals("짧은 휴식은 1초부터 60분까지 입력할 수 있어요.", defaultsError(1500, 0, 900, 4))
        assertEquals("긴 휴식 간격은 2회부터 12회까지 입력할 수 있어요.", defaultsError(1500, 300, 900, 13))
    }

    @Test
    fun validatedDefaultsCarryOnlyNonNullTypedValues() {
        val valid = assertIs<TimeDefaultsValidation.Valid>(
            validateTimeDefaults(focusSeconds = 1, shortBreakSeconds = 3_600, longBreakSeconds = 7_200, longBreakEvery = 12),
        )

        assertEquals(TimeDefaults(1, 3_600, 7_200, 12), valid.defaults)
    }

    @Test
    fun invalidDefaultsKeepTheExistingValidationOrderAndMessages() {
        val invalid = assertIs<TimeDefaultsValidation.Invalid>(
            validateTimeDefaults(focusSeconds = null, shortBreakSeconds = 0, longBreakSeconds = 0, longBreakEvery = 1),
        )

        assertEquals("집중 시간은 1초부터 240분까지 입력할 수 있어요.", invalid.message)
    }

    @Test
    fun durationFieldsRoundTripSubMinuteAndNonRoundDurations() {
        assertEquals(DurationFields("0", "30"), splitDurationSeconds(30))
        assertEquals(DurationFields("25", "17"), splitDurationSeconds(1517))
        assertEquals(30, durationSeconds("0", "30"))
        assertEquals(1517, durationSeconds("25", "17"))
        assertEquals(null, durationSeconds("1", "60"))
    }

    @Test
    fun phaseMaximumAllowsOnlyZeroSecondsAtTheMaximumMinute() {
        assertNull(currentError(durationSeconds("60", "00"), 3600, 0))
        assertEquals("전체 목표는 1초부터 60분까지 입력할 수 있어요.",
            currentError(durationSeconds("60", "01"), 3600, 0))
        assertNull(defaultsError(durationSeconds("240", "00"), 300, 900, 4))
        assertEquals("집중 시간은 1초부터 240분까지 입력할 수 있어요.",
            defaultsError(durationSeconds("240", "01"), 300, 900, 4))
    }

    @Test
    fun editingOneDefaultDoesNotRoundTheOtherStoredDurations() {
        val original = Subject(
            id = "study",
            name = "Study",
            focusSeconds = 1517,
            shortBreakSeconds = 337,
            longBreakSeconds = 947,
        )
        val shortFields = splitDurationSeconds(original.shortBreakSeconds)
        val longFields = splitDurationSeconds(original.longBreakSeconds)
        val edited = original.copy(
            focusSeconds = durationSeconds("30", "45")!!,
            shortBreakSeconds = durationSeconds(shortFields.minutes, shortFields.seconds)!!,
            longBreakSeconds = durationSeconds(longFields.minutes, longFields.seconds)!!,
        )

        assertEquals(1845, edited.focusSeconds)
        assertEquals(337, edited.shortBreakSeconds)
        assertEquals(947, edited.longBreakSeconds)
    }

    private fun currentError(totalSeconds: Int?, maxSeconds: Int, elapsedMillis: Long): String? =
        (validateCurrentDuration(totalSeconds, maxSeconds, elapsedMillis) as? CurrentDurationValidation.Invalid)?.message

    private fun defaultsError(focus: Int?, short: Int?, long: Int?, every: Int?): String? =
        (validateTimeDefaults(focus, short, long, every) as? TimeDefaultsValidation.Invalid)?.message
}
