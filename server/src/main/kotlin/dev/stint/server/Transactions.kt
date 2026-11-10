package dev.stint.server

import java.sql.Connection

/** Rolls back without hiding the exception that caused the transaction to fail. */
internal fun Connection.rollbackAndRethrow(error: Exception): Nothing {
    try {
        rollback()
    } catch (rollbackError: Exception) {
        error.addSuppressed(rollbackError)
    }
    throw error
}
