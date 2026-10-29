package dev.stint.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimerClockTest {
    @Test
    fun twentyFiveMinuteSessionUsesOnlyTwentyFiveOfSixtyMinutesOfSand() {
        val initial = countdownClockReading(1500, 1500)
        assertEquals(25f / 60f, initial.ringFraction)
        assertEquals(25f / 60f, initial.upperSandFraction)
        assertEquals(0f, initial.lowerSandFraction)
        val midway = countdownClockReading(600, 1500)
        assertEquals(10f / 60f, midway.upperSandFraction)
        assertEquals(15f / 60f, midway.lowerSandFraction)
        assertEquals(initial.upperSandFraction, midway.upperSandFraction + midway.lowerSandFraction, .00001f)
    }

    @Test
    fun fullHourAndEmptyClockHaveExactBoundaries() {
        val full = countdownClockReading(3600, 3600)
        assertEquals(1f, full.ringFraction)
        assertEquals(0f, full.lowerSandFraction)
        val done = countdownClockReading(0, 1500)
        assertEquals(0f, done.upperSandFraction)
        assertEquals(25f / 60f, done.lowerSandFraction)
        assertEquals(0f, countdownClockReading(-1, 1500).ringFraction)
    }

    @Test
    fun longerCountdownShowsTheWholeTimeAndAnAdditionalHour() {
        val initial = countdownClockReading(5400, 5400)
        assertEquals(5400, initial.valueSeconds)
        assertEquals(1, initial.extraHours)
        assertEquals(.5f, initial.ringFraction)
        assertEquals(0f, initial.lowerSandFraction)
        val nextGlass = countdownClockReading(3600, 5400)
        assertEquals(0, nextGlass.extraHours)
        assertEquals(1f, nextGlass.upperSandFraction)
        assertEquals(0f, nextGlass.lowerSandFraction)
    }

    @Test
    fun stopwatchUsesElapsedTimeAndRetainsCompletedHours() {
        assertEquals(1f, stopwatchClockReading(0).upperSandFraction)
        val reading = stopwatchClockReading(4500)
        assertEquals(1, reading.extraHours)
        assertEquals(.25f, reading.ringFraction)
        assertEquals(.75f, reading.upperSandFraction)
        assertEquals(.25f, reading.lowerSandFraction)
        assertTrue(reading.countingUp)
        val nextHour = stopwatchClockReading(3600)
        assertEquals(1, nextHour.extraHours)
        assertEquals(0f, nextHour.ringFraction)
        assertEquals(1f, nextHour.upperSandFraction)
    }

    @Test
    fun displayIncludesHoursInsteadOfTruncatingLongDurations() {
        assertEquals("25:00", formatClockTime(1500))
        assertEquals("1:30:05", formatClockTime(5405))
        assertEquals("00:00", formatClockTime(-1))
        assertEquals(TimerClockStyle.ORBIT, TimerClockStyle.fromStored("obsolete"))
        assertEquals(TimerClockStyle.HOURGLASS, TimerClockStyle.fromStored("HOURGLASS"))
    }
}
