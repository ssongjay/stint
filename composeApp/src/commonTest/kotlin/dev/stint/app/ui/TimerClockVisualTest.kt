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
}
