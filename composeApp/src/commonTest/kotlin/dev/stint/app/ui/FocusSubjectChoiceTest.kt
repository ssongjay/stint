package dev.stint.app.ui

import dev.stint.shared.Subject
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusSubjectChoiceTest {
    private val first = Subject("first", "읽기", focusSeconds = 1_500)
    private val selected = Subject("selected", "공부", focusSeconds = 900)
    private val hidden = Subject("hidden", "보관", archived = true)
    private val subjects = listOf(hidden, first, selected)

    @Test
    fun idleChoiceTakesPriorityOverTheFirstAndLastUsedSubjects() {
        val timer = TimerState(subjectId = first.id)
        assertEquals(selected, focusSubjectChoice(subjects, timer, selected.id))
        assertEquals(TimerState(subjectId = first.id), timer)
    }

    @Test
    fun runningAndPausedTimersKeepTheirSubjectIncludingWaitingBreaks() {
        listOf(TimerStatus.RUNNING, TimerStatus.PAUSED).forEach { status ->
            listOf(TimerPhase.FOCUS, TimerPhase.SHORT_BREAK, TimerPhase.LONG_BREAK).forEach { phase ->
                assertEquals(first, focusSubjectChoice(subjects,
                    TimerState(subjectId = first.id, status = status, phase = phase), selected.id))
            }
        }
    }

    @Test
    fun archivedOrMissingDraftFallsBackToAnAvailableLastUsedSubject() {
        val timer = TimerState(subjectId = selected.id)
        assertEquals(selected, focusSubjectChoice(subjects, timer, hidden.id))
        assertEquals(selected, focusSubjectChoice(subjects, timer, "removed"))
        assertEquals(first, focusSubjectChoice(subjects, TimerState(subjectId = hidden.id), null))
    }

    @Test
    fun activeArchivedSubjectRemainsVisibleAndMissingActiveSubjectIsNeverReplaced() {
        assertEquals(hidden, focusSubjectChoice(subjects,
            TimerState(subjectId = hidden.id, status = TimerStatus.PAUSED), first.id))
        assertNull(focusSubjectChoice(subjects,
            TimerState(subjectId = "missing", status = TimerStatus.RUNNING), first.id))
    }

    @Test
    fun emptyAndArchivedOnlyListsHaveNoStartableSubject() {
        assertNull(focusSubjectChoice(emptyList(), TimerState(), selected.id))
        assertNull(focusSubjectChoice(listOf(hidden), TimerState(), hidden.id))
    }

    @Test
    fun selectionUsesTheLatestSubjectNameAndDuration() {
        val renamed = selected.copy(name = "바뀐 주제", focusSeconds = 2_700)
        assertEquals(renamed, focusSubjectChoice(listOf(first, renamed), TimerState(), selected.id))
    }
}
