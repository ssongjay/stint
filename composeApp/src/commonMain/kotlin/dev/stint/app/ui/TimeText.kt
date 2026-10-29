package dev.stint.app.ui

import dev.stint.shared.FocusSession
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal fun formatTimer(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds.coerceAtLeast(0) / 60
    return when {
        minutes < 60 -> "${minutes}분"
        minutes % 60 == 0 -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun localDateTime(epochMs: Long) =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())

internal fun localDate(epochMs: Long): LocalDate = localDateTime(epochMs).date

internal fun formatLocalTime(epochMs: Long): String {
    val time = localDateTime(epochMs).time
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

internal fun formatLocalDate(epochMs: Long): String {
    val date = localDate(epochMs)
    return "${date.month.ordinal + 1}월 ${date.day}일"
}

internal fun relativeTime(epochMs: Long, nowEpochMs: Long): String {
    val seconds = ((nowEpochMs - epochMs).coerceAtLeast(0) / 1000).toInt()
    return when {
        seconds < 60 -> "방금"
        seconds < 3600 -> "${seconds / 60}분 전"
        seconds < 86_400 -> "${seconds / 3600}시간 전"
        else -> formatLocalDate(epochMs)
    }
}

internal fun FocusSession.localDay(): LocalDate = localDate(endedAtEpochMs)

internal fun weekdayKorean(date: LocalDate): String = when (date.dayOfWeek.ordinal) {
    0 -> "월"
    1 -> "화"
    2 -> "수"
    3 -> "목"
    4 -> "금"
    5 -> "토"
    else -> "일"
}
