package dev.stint.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeTextTest {
    @Test fun durationLabelsRetainSecondPrecision() {
        assertEquals("30초", formatDuration(30))
        assertEquals("25분 17초", formatDuration(1_517))
        assertEquals("2시간 17초", formatDuration(7_217))
        assertEquals("0분", formatDuration(0))
        assertEquals("25분", formatDuration(1_500))
    }
}
