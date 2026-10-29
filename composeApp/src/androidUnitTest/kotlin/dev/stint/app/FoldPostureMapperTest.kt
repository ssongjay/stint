package dev.stint.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldPostureMapperTest {
    @Test
    fun `vertical separating hinge maps to book posture in dp`() {
        val posture = mapFoldPosture(FoldFeatureGeometry(
            halfOpened = false,
            horizontal = false,
            separating = true,
            leftPx = 1080,
            topPx = 0,
            widthPx = 12,
            heightPx = 1800,
        ), density = 3f)

        assertTrue(posture.isBook)
        assertFalse(posture.isTabletop)
        assertEquals(360f, posture.hingeLeftDp)
        assertEquals(4f, posture.hingeWidthDp)
        assertEquals(600f, posture.hingeHeightDp)
    }

    @Test
    fun `horizontal half opened hinge maps to tabletop posture`() {
        val posture = mapFoldPosture(FoldFeatureGeometry(
            halfOpened = true,
            horizontal = true,
            separating = true,
            leftPx = 0,
            topPx = 900,
            widthPx = 1800,
            heightPx = 8,
        ), density = 2f)

        assertTrue(posture.isTabletop)
        assertFalse(posture.isBook)
        assertEquals(450f, posture.hingeTopDp)
        assertEquals(4f, posture.hingeHeightDp)
    }

    @Test
    fun `vertical flat feature still maps the unfolded inner display to book posture`() {
        val posture = mapFoldPosture(FoldFeatureGeometry(
            halfOpened = false,
            horizontal = false,
            separating = false,
            leftPx = 0,
            topPx = 0,
            widthPx = 0,
            heightPx = 0,
        ), density = 0f)

        assertTrue(posture.isBook)
        assertFalse(posture.isTabletop)
    }

    @Test
    fun `absence of a folding feature keeps the outer display in ordinary layout`() {
        val posture = mapFoldPosture(null, density = 3f)

        assertFalse(posture.isBook)
        assertFalse(posture.isTabletop)
    }
}
