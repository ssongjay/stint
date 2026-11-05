package dev.stint.app.ui

import dev.stint.shared.FocusSession
import dev.stint.shared.Snapshot
import dev.stint.shared.TimerMode
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoHistoryTest {
    @Test
    fun serverWriteOrderWinsOverSessionDateAndDuplicateWhitespaceIsRemoved() {
        val snapshot = Snapshot(1, recentMemos = listOf(" 이전 기록에 방금 쓴 회고 ", "", "다음", "다음", "셋", "넷", "다섯", "여섯"),
            sessions = listOf(session("newer-session", 9, "기록 날짜가 더 최근")))
        assertEquals(listOf("이전 기록에 방금 쓴 회고", "다음", "셋", "넷", "다섯"), recentMemoSuggestions(snapshot))
    }

    @Test
    fun oldSnapshotsUseAllAvailableSessionsInReverseCompletionOrder() {
        val snapshot = Snapshot(1, sessions = listOf(session("a", 1, "이전"), session("b", 4, "최근"),
            session("c", 3, " 최근 "), session("d", 5, "  "), session("e", 2, "중간")))
        assertEquals(listOf("최근", "중간", "이전"), recentMemoSuggestions(snapshot))
    }

    @Test
    fun noHistoryAndOversizedLegacyValuesDoNotProduceGhostSuggestions() {
        assertEquals(emptyList(), recentMemoSuggestions(null))
        assertEquals(emptyList(), recentMemoSuggestions(Snapshot(1, recentMemos = listOf(" ", "x".repeat(4001)))))
    }

    private fun session(id: String, end: Long, memo: String) = FocusSession(id, "study", 0, end, 1, TimerMode.COUNTDOWN, memo)
}
