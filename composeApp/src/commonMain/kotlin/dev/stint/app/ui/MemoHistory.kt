package dev.stint.app.ui

import dev.stint.shared.Snapshot

/** Prefer server write order; old caches fall back to the latest completed records. */
internal fun recentMemoSuggestions(snapshot: Snapshot?): List<String> {
    val source = snapshot?.recentMemos?.takeIf { it.isNotEmpty() }
        ?: snapshot?.sessions.orEmpty().sortedByDescending { it.endedAtEpochMs }.map { it.memo }
    return source.asSequence().map(String::trim).filter { it.isNotEmpty() && it.length <= 4_000 }
        .distinct().take(5).toList()
}
