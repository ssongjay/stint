package dev.stint.app.ui

internal data class TimeDefaults(
    val focusSeconds: Int,
    val shortBreakSeconds: Int,
    val longBreakSeconds: Int,
    val longBreakEvery: Int,
)

internal sealed interface TimeDefaultsValidation {
    data class Valid(val defaults: TimeDefaults) : TimeDefaultsValidation
    data class Invalid(val message: String) : TimeDefaultsValidation
}

internal fun validateTimeDefaults(
    focusSeconds: Int?,
    shortBreakSeconds: Int?,
    longBreakSeconds: Int?,
    longBreakEvery: Int?,
): TimeDefaultsValidation {
    if (focusSeconds == null || focusSeconds !in 1..14_400) {
        return TimeDefaultsValidation.Invalid("집중 시간은 1초부터 240분까지 입력할 수 있어요.")
    }
    if (shortBreakSeconds == null || shortBreakSeconds !in 1..3_600) {
        return TimeDefaultsValidation.Invalid("짧은 휴식은 1초부터 60분까지 입력할 수 있어요.")
    }
    if (longBreakSeconds == null || longBreakSeconds !in 1..7_200) {
        return TimeDefaultsValidation.Invalid("긴 휴식은 1초부터 120분까지 입력할 수 있어요.")
    }
    if (longBreakEvery == null || longBreakEvery !in 2..12) {
        return TimeDefaultsValidation.Invalid("긴 휴식 간격은 2회부터 12회까지 입력할 수 있어요.")
    }
    return TimeDefaultsValidation.Valid(
        TimeDefaults(focusSeconds, shortBreakSeconds, longBreakSeconds, longBreakEvery),
    )
}
