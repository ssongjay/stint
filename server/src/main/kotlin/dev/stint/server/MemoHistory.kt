package dev.stint.server

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun memoHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
