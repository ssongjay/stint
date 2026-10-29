package dev.stint.app.ui

import androidx.compose.runtime.Composable

private val NoTimeDialHaptics: () -> Unit = {}

@Composable
internal actual fun rememberTimeDialHaptics(): () -> Unit = NoTimeDialHaptics
