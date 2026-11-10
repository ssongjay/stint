package dev.stint.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldContentGeometryTest {
    @Test
    fun windowHingeIsProjectedIntoTheContentCoordinateSpace() {
        val geometry = resolveFoldContentGeometry(
            posture = FoldPosture(
                isBook = true,
                isTabletop = true,
                hingeLeftDp = 420f,
                hingeTopDp = 500f,
                hingeWidthDp = 8f,
                hingeHeightDp = 10f,
            ),
            contentOriginXDp = 20f,
            contentOriginYDp = 40f,
            contentWidthDp = 800f,
            contentHeightDp = 900f,
        )

        assertEquals(400f, geometry.hingeLeftDp)
        assertEquals(460f, geometry.hingeTopDp)
        assertTrue(geometry.validBook)
        assertTrue(geometry.validTabletop)
    }

    @Test
    fun hingeOutsideContentIsClampedAndRejected() {
        val geometry = resolveFoldContentGeometry(
            posture = FoldPosture(isBook = true, isTabletop = true, hingeLeftDp = 10f, hingeTopDp = 20f),
            contentOriginXDp = 30f,
            contentOriginYDp = 40f,
            contentWidthDp = 800f,
            contentHeightDp = 900f,
        )

        assertEquals(0f, geometry.hingeLeftDp)
        assertEquals(0f, geometry.hingeTopDp)
        assertFalse(geometry.validBook)
        assertFalse(geometry.validTabletop)
    }

    @Test
    fun eachPaneMustRemainLargerThanTheExistingMinimum() {
        val leadingEdge = resolveFoldContentGeometry(
            posture = FoldPosture(isBook = true, hingeLeftDp = 120f, hingeWidthDp = 8f),
            contentOriginXDp = 0f,
            contentOriginYDp = 0f,
            contentWidthDp = 800f,
            contentHeightDp = 900f,
        )
        val trailingEdge = resolveFoldContentGeometry(
            posture = FoldPosture(isTabletop = true, hingeTopDp = 772f, hingeHeightDp = 8f),
            contentOriginXDp = 0f,
            contentOriginYDp = 0f,
            contentWidthDp = 800f,
            contentHeightDp = 900f,
        )

        assertFalse(leadingEdge.validBook)
        assertFalse(trailingEdge.validTabletop)
    }
}
