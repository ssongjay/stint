package dev.stint.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoEditorTest {
    @Test
    fun choicesPreserveRecencyWhileTrimmingAndRemovingDuplicates() {
        assertEquals(
            listOf("첫째", "둘째", "셋째"),
            memoEditorChoices(listOf("  첫째  ", "둘째", "첫째", "", "   ", "셋째"), ""),
        )
    }

    @Test
    fun currentMemoIsNotSuggestedBackToItsOwnEditor() {
        assertEquals(
            listOf("새 회고", "다른 회고"),
            memoEditorChoices(listOf("현재 회고", "새 회고", " 현재 회고 ", "다른 회고"), " 현재 회고 "),
        )
    }

    @Test
    fun choicesAreBoundedAfterDeduplication() {
        assertEquals(
            listOf("6", "5", "4", "3", "2"),
            memoEditorChoices(listOf("6", "5", "5", "4", "3", "2", "1"), "", limit = 5),
        )
    }

    @Test
    fun nonPositiveLimitProducesNoChoices() {
        assertEquals(emptyList(), memoEditorChoices(listOf("회고"), "", limit = 0))
        assertEquals(emptyList(), memoEditorChoices(listOf("회고"), "", limit = -1))
    }

    @Test
    fun acceptedChoicePlacesCursorAtTheEnd() {
        val value = memoWithCursorAtEnd("직전 회고")
        assertEquals("직전 회고", value.text)
        assertEquals(value.text.length, value.selection.start)
        assertEquals(value.text.length, value.selection.end)
    }
}
