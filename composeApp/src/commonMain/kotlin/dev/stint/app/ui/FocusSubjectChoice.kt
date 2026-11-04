package dev.stint.app.ui

import dev.stint.shared.Subject
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus

/** A draft choice never changes the subject of a timer that already exists. */
internal fun focusSubjectChoice(subjects: List<Subject>, timer: TimerState, selectedId: String?): Subject? {
    if (timer.status != TimerStatus.IDLE) return subjects.firstOrNull { it.id == timer.subjectId }
    val available = subjects.filterNot { it.archived }
    return available.firstOrNull { it.id == selectedId }
        ?: available.firstOrNull { it.id == timer.subjectId }
        ?: available.firstOrNull()
}
