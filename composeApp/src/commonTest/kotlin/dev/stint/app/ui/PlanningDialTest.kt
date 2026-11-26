package dev.stint.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanningDialTest {
    @Test
    fun `segment containment handles the twelve oclock wrap`() {
        assertTrue(planningTurnContains(-1f / 24f, 1f / 24f, 0f))
        assertTrue(planningTurnContains(-1f / 24f, 1f / 24f, .99f))
        assertTrue(planningTurnContains(-1f / 24f, 1f / 24f, .01f))
        assertFalse(planningTurnContains(-1f / 24f, 1f / 24f, .1f))
    }

    @Test
    fun `hit testing returns the painted segment without changing geometry`() {
        val segments = (0 until 12).map { index ->
            PlanningDialSegment(
                from = index / 12f - 1f / 24f,
                to = index / 12f + 1f / 24f,
                color = Color.Black,
            )
        }
        val size = Size(320f, 320f)
        fun point(turn: Float, radius: Float = 110f): Offset {
            val angle = turn * 2f * PI.toFloat() - PI.toFloat() / 2f
            return Offset(
                size.width / 2f + cos(angle) * radius,
                size.height / 2f + sin(angle) * radius,
            )
        }

        assertEquals(0, planningDialSegmentAt(point(0f), size, segments))
        assertEquals(3, planningDialSegmentAt(point(.25f), size, segments))
        assertEquals(11, planningDialSegmentAt(point(11f / 12f), size, segments))
        assertNull(planningDialSegmentAt(point(.25f, radius = 40f), size, segments))
        assertNull(planningDialSegmentAt(point(.25f, radius = 150f), size, segments))
    }

    @Test
    fun `minute cycle boundaries select the intended focus and rest arc`() {
        val segments = listOf(
            PlanningDialSegment(0f, 15f / 60f, Color.Black),
            PlanningDialSegment(15f / 60f, 20f / 60f, Color.White),
            PlanningDialSegment(20f / 60f, 35f / 60f, Color.Black),
            PlanningDialSegment(35f / 60f, 40f / 60f, Color.White),
            PlanningDialSegment(40f / 60f, 55f / 60f, Color.Black),
            PlanningDialSegment(55f / 60f, 1f, Color.White),
        )
        val size = Size(400f, 400f)
        fun point(minute: Float): Offset {
            val angle = minute / 60f * 2f * PI.toFloat() - PI.toFloat() / 2f
            return Offset(200f + cos(angle) * 135f, 200f + sin(angle) * 135f)
        }

        assertEquals(0, planningDialSegmentAt(point(7f), size, segments))
        assertEquals(1, planningDialSegmentAt(point(17f), size, segments))
        assertEquals(5, planningDialSegmentAt(point(58f), size, segments))
    }
}
