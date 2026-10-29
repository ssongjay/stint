package dev.stint.shared

const val CLOCK_FACE_SECONDS: Int = 60 * 60

enum class TimerClockStyle {
    ORBIT,
    HOURGLASS;

    companion object {
        fun fromStored(value: String?): TimerClockStyle = entries.firstOrNull { it.name == value } ?: ORBIT
    }
}

data class TimerClockReading(
    val valueSeconds: Int,
    val ringFraction: Float,
    val upperSandFraction: Float,
    val lowerSandFraction: Float,
    val extraHours: Int,
    val countingUp: Boolean,
)

/** One face/glass always represents 60 minutes, independently of the configured session. */
fun countdownClockReading(remainingSeconds: Int, targetSeconds: Int): TimerClockReading {
    val target = targetSeconds.coerceAtLeast(0)
    val remaining = remainingSeconds.coerceIn(0, target)
    val extraHours = if (remaining > 0) (remaining - 1) / CLOCK_FACE_SECONDS else 0
    val withinHour = remaining - extraHours * CLOCK_FACE_SECONDS
    val currentChunk = (target - extraHours * CLOCK_FACE_SECONDS).coerceIn(0, CLOCK_FACE_SECONDS)
    val upper = withinHour.toFloat() / CLOCK_FACE_SECONDS
    val lower = (currentChunk - withinHour).coerceAtLeast(0).toFloat() / CLOCK_FACE_SECONDS
    return TimerClockReading(remaining, upper, upper, lower, extraHours, countingUp = false)
}

/** Stopwatch glasses count elapsed hours; a new glass starts after each complete hour. */
fun stopwatchClockReading(elapsedSeconds: Int): TimerClockReading {
    val elapsed = elapsedSeconds.coerceAtLeast(0)
    val extraHours = elapsed / CLOCK_FACE_SECONDS
    val fraction = (elapsed % CLOCK_FACE_SECONDS).toFloat() / CLOCK_FACE_SECONDS
    return TimerClockReading(elapsed, fraction, 1f - fraction, fraction, extraHours, countingUp = true)
}

fun formatClockTime(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val minutes = (safe / 60) % 60
    val tail = "${minutes.toString().padStart(2, '0')}:${(safe % 60).toString().padStart(2, '0')}"
    return if (safe >= CLOCK_FACE_SECONDS) "${safe / CLOCK_FACE_SECONDS}:$tail" else tail
}
