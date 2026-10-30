package dev.stint.app

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTimerPopoverTest {
    @Test
    fun `popover stays within the screen containing the menu item`() {
        val screens = listOf(
            DesktopScreenBounds(-1_920, 24, 1_920, 1_056),
            DesktopScreenBounds(0, 24, 1_440, 876),
        )

        assertEquals(
            Point(1_110, 34),
            timerPopoverLocation(Point(1_430, 24), 330, 438, screens),
        )
        assertEquals(
            Point(-1_920, 34),
            timerPopoverLocation(Point(-1_915, 24), 330, 438, screens),
        )
    }

    @Test
    fun `popover opens above an anchor near the bottom`() {
        val screens = listOf(DesktopScreenBounds(0, 24, 1_440, 876))

        assertEquals(
            Point(555, 352),
            timerPopoverLocation(Point(720, 800), 330, 438, screens),
        )
    }
}
