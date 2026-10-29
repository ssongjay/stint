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
    return countdownClockReadingMillis(remainingSeconds.toLong() * 1000, targetSeconds.toLong() * 1000)
}

/** Fractions retain subsecond precision while the number uses the existing remaining-second ceiling. */
fun countdownClockReadingMillis(remainingMillis: Long, targetMillis: Long): TimerClockReading {
    val face = CLOCK_FACE_SECONDS * 1000L
    val target = targetMillis.coerceIn(0, Int.MAX_VALUE * 1000L)
    val remaining = remainingMillis.coerceIn(0, target)
    val extraHours = if (remaining > 0) ((remaining - 1) / face).toInt() else 0
    val withinHour = remaining - extraHours * face
    val currentChunk = (target - extraHours * face).coerceIn(0, face)
    val upper = withinHour.toFloat() / face
    val lower = (currentChunk - withinHour).coerceAtLeast(0).toFloat() / face
    val valueSeconds = ((remaining + 999) / 1000L).toInt()
    return TimerClockReading(valueSeconds, upper, upper, lower, extraHours, countingUp = false)
}

/** Stopwatch glasses count elapsed hours; a new glass starts after each complete hour. */
fun stopwatchClockReading(elapsedSeconds: Int): TimerClockReading {
    return stopwatchClockReadingMillis(elapsedSeconds.toLong() * 1000)
}

fun stopwatchClockReadingMillis(elapsedMillis: Long): TimerClockReading {
    val elapsed = elapsedMillis.coerceIn(0, Int.MAX_VALUE * 1000L)
    val face = CLOCK_FACE_SECONDS * 1000L
    val extraHours = (elapsed / face).toInt()
    val fraction = (elapsed % face).toFloat() / face
    return TimerClockReading((elapsed / 1000L).toInt(), fraction, 1f - fraction, fraction, extraHours, countingUp = true)
}

fun formatClockTime(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val minutes = (safe / 60) % 60
    val tail = "${minutes.toString().padStart(2, '0')}:${(safe % 60).toString().padStart(2, '0')}"
    return if (safe >= CLOCK_FACE_SECONDS) "${safe / CLOCK_FACE_SECONDS}:$tail" else tail
}
