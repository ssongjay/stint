package dev.stint.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

internal data class DurationFields(val minutes: String, val seconds: String)

internal fun splitDurationSeconds(totalSeconds: Int): DurationFields {
    val safe = totalSeconds.coerceAtLeast(0)
    return DurationFields((safe / 60).toString(), (safe % 60).toString().padStart(2, '0'))
}

internal fun durationSeconds(minutes: String, seconds: String): Int? {
    val minuteValue = minutes.toIntOrNull() ?: return null
    val secondValue = seconds.toIntOrNull() ?: return null
    if (minuteValue !in 0..999 || secondValue !in 0..59) return null
    return minuteValue * 60 + secondValue
}

internal fun durationMinuteDigits(value: String): String = value.filter(Char::isDigit).take(3)

internal fun durationSecondDigits(value: String): String = value.filter(Char::isDigit).take(2)

@Composable
internal fun DurationFieldsInput(
    label: String,
    minutes: String,
    seconds: String,
    onMinutes: (String) -> Unit,
    onSeconds: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = minutes,
            onValueChange = { onMinutes(durationMinuteDigits(it)) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            label = { Text("$label · 분") },
            suffix = { Text("분") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = seconds,
            onValueChange = { onSeconds(durationSecondDigits(it)) },
            enabled = enabled,
            modifier = Modifier.weight(.72f),
            label = { Text("$label · 초") },
            suffix = { Text("초") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}
