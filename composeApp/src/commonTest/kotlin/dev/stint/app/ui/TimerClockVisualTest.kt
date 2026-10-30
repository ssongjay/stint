package dev.stint.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TimerClockVisualTest {
    @Test
    fun minuteSecondReadoutKeepsOneSizingSetAcrossTickBoundaries() {
        val before = clockTimeSizingSamples("09:59")
        val after = clockTimeSizingSamples("10:00")

        assertEquals(before, after)
        assertEquals(10, before.size)
        assertTrue(before.all { it.length == "00:00".length })
    }

    @Test
    fun hourReadoutOnlyChangesSizingWhenTheHourGainsADigit() {
        val oneHour = clockTimeSizingSamples("1:59:59")
        val nineHours = clockTimeSizingSamples("9:00:00")
        val tenHours = clockTimeSizingSamples("10:00:00")

        assertEquals(oneHour, nineHours)
        assertNotEquals(nineHours, tenHours)
        assertTrue(oneHour.all { it.length == "0:00:00".length })
        assertTrue(tenHours.all { it.length == "00:00:00".length })
    }

    @Test
    fun sixtyMinuteCountdownKeepsHourSizedReadoutBelowTheHourBoundary() {
        val initial = clockTimeSizingSamples(
            time = "1:00:00",
            sizingReferenceTime = "1:00:00",
        )
        val afterFirstSecond = clockTimeSizingSamples(
            time = "59:59",
            sizingReferenceTime = "1:00:00",
        )

        assertEquals(initial, afterFirstSecond)
        assertTrue(afterFirstSecond.all { it.length == "0:00:00".length })
    }

    @Test
    fun sizingSetCoversEveryNumeralEvenWithoutTabularFontSupport() {
        val samples = clockTimeSizingSamples("24:39")

        assertEquals((0..9).map { "$it$it:$it$it" }, samples)
    }

    @Test
    fun countdownKeepsTotalMinutesWhileStopwatchKeepsElapsedHours() {
        assertEquals("120:00", formatTimerClockReadout(7_200, countingUp = false))
        assertEquals("2:00:00", formatTimerClockReadout(7_200, countingUp = true))
        assertEquals(
            clockTimeSizingSamples("99:59", "120:00"),
            clockTimeSizingSamples("120:00", "120:00"),
        )
        assertTrue(clockTimeSizingSamples("99:59", "120:00").all { it.length == "000:00".length })
    }

    @Test
    fun countdownFontSizeReferenceRetainsMaximumPhaseDigits() {
        assertEquals(3, countdownMinuteSlotDigits("14:42", "240:00"))
        assertEquals(3, countdownMinuteSlotDigits("100:00", "25:00"))
        assertEquals(2, countdownMinuteSlotDigits("09:59", "25:00"))
    }

    @Test
    fun visibleMinuteSlotsExcludeUnusedPhaseDigits() {
        assertEquals(2, countdownMinuteSlotDigits("00:18"))
        assertEquals(2, countdownMinuteSlotDigits("14:42"))
        assertEquals(2, countdownMinuteSlotDigits("99:59"))
        assertEquals(3, countdownMinuteSlotDigits("100:00"))
        assertEquals(3, countdownMinuteSlotDigits("240:00"))
        assertEquals(3, countdownMinuteSlotDigits("14:42", "240:00"))
    }
}
