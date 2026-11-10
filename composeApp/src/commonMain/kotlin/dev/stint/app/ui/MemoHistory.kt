package dev.stint.app.ui

import dev.stint.shared.Snapshot

/**
 * 최근에 완료한 세션과 최근에 작성한 회고는 순서가 다를 수 있다.
 * 서버의 작성 순서를 우선하고, 이전 버전 캐시에 목록이 없을 때만 세션 종료 순서로 보완한다.
 */
internal fun recentMemoSuggestions(snapshot: Snapshot?): List<String> {
    val source = snapshot?.recentMemos?.takeIf { it.isNotEmpty() }
        ?: snapshot?.sessions.orEmpty().sortedByDescending { it.endedAtEpochMs }.map { it.memo }
    return source.asSequence().map(String::trim).filter { it.isNotEmpty() && it.length <= 4_000 }
        .distinct().take(5).toList()
}
